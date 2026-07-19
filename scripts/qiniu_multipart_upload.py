#!/usr/bin/env python3
"""Upload a file to Qiniu Kodo through Multipart Upload v2."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

MIN_PART_SIZE = 1 << 20
DEFAULT_PART_SIZE = 4 << 20
DEFAULT_CONCURRENCY = 3
MAX_PARTS = 10_000


def encode_object_name(object_key: str) -> str:
    if not object_key:
        return "~"
    return base64.urlsafe_b64encode(object_key.encode("utf-8")).decode("ascii")


def normalize_upload_host(upload_host: str) -> str:
    return upload_host.rstrip("/")


def request_json(
    *,
    method: str,
    url: str,
    headers: dict[str, str],
    data: bytes | None = None,
    timeout: int = 120,
) -> dict[str, object]:
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = response.read()
    if not payload:
        return {}
    return json.loads(payload.decode("utf-8"))


def initiate_upload(upload_host: str, bucket: str, object_key: str, upload_token: str) -> str:
    object_name = encode_object_name(object_key)
    url = f"{normalize_upload_host(upload_host)}/buckets/{bucket}/objects/{object_name}/uploads"
    result = request_json(
        method="POST",
        url=url,
        headers={"Authorization": f"UpToken {upload_token}"},
    )
    upload_id = str(result.get("uploadId", "")).strip()
    if not upload_id:
        raise RuntimeError(f"Qiniu initiateMultipartUpload did not return uploadId: {result}")
    return upload_id


def upload_part(
    upload_host: str,
    bucket: str,
    object_key: str,
    upload_token: str,
    upload_id: str,
    part_number: int,
    chunk: bytes,
) -> dict[str, object]:
    object_name = encode_object_name(object_key)
    url = (
        f"{normalize_upload_host(upload_host)}/buckets/{bucket}/objects/{object_name}/uploads/"
        f"{upload_id}/{part_number}"
    )
    content_md5 = base64.b64encode(hashlib.md5(chunk).digest()).decode("ascii")
    return request_json(
        method="PUT",
        url=url,
        data=chunk,
        headers={
            "Authorization": f"UpToken {upload_token}",
            "Content-Type": "application/octet-stream",
            "Content-MD5": content_md5,
            "Content-Length": str(len(chunk)),
        },
        timeout=300,
    )


def complete_upload(
    upload_host: str,
    bucket: str,
    object_key: str,
    upload_token: str,
    upload_id: str,
    parts: list[dict[str, object]],
    file_name: str | None,
    mime_type: str | None,
) -> dict[str, object]:
    object_name = encode_object_name(object_key)
    url = f"{normalize_upload_host(upload_host)}/buckets/{bucket}/objects/{object_name}/uploads/{upload_id}"
    payload: dict[str, object] = {"parts": parts}
    if file_name:
        payload["fname"] = file_name
    if mime_type:
        payload["mimeType"] = mime_type
    return request_json(
        method="POST",
        url=url,
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={
            "Authorization": f"UpToken {upload_token}",
            "Content-Type": "application/json",
        },
        timeout=300,
    )


def abort_upload(upload_host: str, bucket: str, object_key: str, upload_token: str, upload_id: str) -> None:
    object_name = encode_object_name(object_key)
    url = f"{normalize_upload_host(upload_host)}/buckets/{bucket}/objects/{object_name}/uploads/{upload_id}"
    try:
        request_json(
            method="DELETE",
            url=url,
            headers={"Authorization": f"UpToken {upload_token}"},
        )
    except Exception as error:  # pragma: no cover - best effort cleanup
        print(f"Warning: failed to abort multipart upload {upload_id}: {error}", file=sys.stderr)


def adjusted_part_size(file_size: int, requested_part_size: int) -> int:
    part_size = max(requested_part_size, MIN_PART_SIZE)
    required_part_size = max(MIN_PART_SIZE, math.ceil(file_size / MAX_PARTS)) if file_size else MIN_PART_SIZE
    if required_part_size > part_size:
        part_size = required_part_size
    return part_size


def infer_mime_type(file_path: Path, explicit_mime_type: str | None) -> str | None:
    if explicit_mime_type:
        return explicit_mime_type
    guessed, _ = mimetypes.guess_type(file_path.name)
    return guessed


def upload_part_with_retry(
    *,
    file_path: Path,
    upload_host: str,
    bucket: str,
    object_key: str,
    upload_token: str,
    upload_id: str,
    part_number: int,
    offset: int,
    chunk_size: int,
    total_parts: int,
) -> dict[str, object]:
    with file_path.open("rb") as input_file:
        input_file.seek(offset)
        chunk = input_file.read(chunk_size)
    if len(chunk) != chunk_size:
        raise RuntimeError(
            f"Unexpected chunk size for part {part_number}: expected {chunk_size}, got {len(chunk)}"
        )

    for attempt in range(1, 4):
        print(
            f"Uploading part {part_number}/{total_parts} attempt {attempt} size={len(chunk)} bytes",
            flush=True,
        )
        try:
            result = upload_part(
                upload_host,
                bucket,
                object_key,
                upload_token,
                upload_id,
                part_number,
                chunk,
            )
            break
        except urllib.error.HTTPError as error:
            if attempt == 3:
                detail = error.read().decode("utf-8", errors="replace")
                raise RuntimeError(
                    f"Qiniu uploadPart failed for part {part_number}: HTTP {error.code} {detail}"
                ) from error
            time.sleep(attempt)
        except urllib.error.URLError as error:
            if attempt == 3:
                raise RuntimeError(f"Qiniu uploadPart failed for part {part_number}: {error}") from error
            time.sleep(attempt)

    etag = str(result.get("etag", "")).strip()
    if not etag:
        raise RuntimeError(f"Qiniu uploadPart did not return etag for part {part_number}: {result}")
    print(f"Uploaded part {part_number}/{total_parts}", flush=True)
    return {"partNumber": part_number, "etag": etag}


def upload_file(
    *,
    file_path: Path,
    object_key: str,
    bucket: str,
    upload_host: str,
    upload_token: str,
    part_size: int,
    concurrency: int,
    file_name: str | None,
    mime_type: str | None,
) -> dict[str, object]:
    file_size = file_path.stat().st_size
    if file_size == 0:
        raise ValueError("Qiniu multipart upload does not support empty files in this workflow")

    actual_part_size = adjusted_part_size(file_size, part_size)
    total_parts = math.ceil(file_size / actual_part_size)
    if total_parts > MAX_PARTS:
        raise ValueError(f"Too many parts: {total_parts} > {MAX_PARTS}")

    upload_id = initiate_upload(upload_host, bucket, object_key, upload_token)
    worker_count = min(max(concurrency, 1), total_parts)
    print(
        f"Started Qiniu multipart upload: key={object_key} size={file_size} bytes "
        f"part_size={actual_part_size} total_parts={total_parts} concurrency={worker_count}",
        flush=True,
    )

    uploaded_parts: list[dict[str, object]] = []
    try:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            future_map = {}
            for part_number in range(1, total_parts + 1):
                offset = (part_number - 1) * actual_part_size
                chunk_size = min(actual_part_size, file_size - offset)
                future = executor.submit(
                    upload_part_with_retry,
                    file_path=file_path,
                    upload_host=upload_host,
                    bucket=bucket,
                    object_key=object_key,
                    upload_token=upload_token,
                    upload_id=upload_id,
                    part_number=part_number,
                    offset=offset,
                    chunk_size=chunk_size,
                    total_parts=total_parts,
                )
                future_map[future] = part_number

            for future in as_completed(future_map):
                try:
                    uploaded_parts.append(future.result())
                except Exception:
                    for pending_future in future_map:
                        pending_future.cancel()
                    raise

        uploaded_parts.sort(key=lambda part: int(part["partNumber"]))

        complete_result = complete_upload(
            upload_host,
            bucket,
            object_key,
            upload_token,
            upload_id,
            uploaded_parts,
            file_name,
            mime_type,
        )
        print(f"Completed Qiniu multipart upload: {json.dumps(complete_result, ensure_ascii=True)}", flush=True)
        return complete_result
    except Exception:
        abort_upload(upload_host, bucket, object_key, upload_token, upload_id)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", required=True, help="local file path")
    parser.add_argument("--key", required=True, help="Qiniu object key")
    parser.add_argument("--bucket", required=True, help="Qiniu bucket name")
    parser.add_argument("--upload-host", default=os.environ.get("QINIU_UPLOAD_HOST", "https://up-z1.qiniup.com"))
    parser.add_argument("--upload-token", required=True, help="Qiniu upload token scoped to the target key")
    parser.add_argument("--part-size", type=int, default=DEFAULT_PART_SIZE, help="part size in bytes")
    parser.add_argument("--concurrency", type=int, default=DEFAULT_CONCURRENCY, help="parallel part uploads")
    parser.add_argument("--file-name", help="original file name recorded in Qiniu")
    parser.add_argument("--mime-type", help="explicit MIME type")
    args = parser.parse_args()

    file_path = Path(args.file).resolve()
    if not file_path.is_file():
        raise FileNotFoundError(f"File not found: {file_path}")

    mime_type = infer_mime_type(file_path, args.mime_type)
    file_name = args.file_name or file_path.name

    upload_file(
        file_path=file_path,
        object_key=args.key,
        bucket=args.bucket,
        upload_host=args.upload_host,
        upload_token=args.upload_token,
        part_size=args.part_size,
        concurrency=args.concurrency,
        file_name=file_name,
        mime_type=mime_type,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
