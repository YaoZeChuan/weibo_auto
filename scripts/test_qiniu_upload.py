#!/usr/bin/env python3
"""Upload a disposable test object to Qiniu and verify it through the public CDN."""

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.request
import uuid

BUCKET = os.environ.get("QINIU_BUCKET", "files")
UPLOAD_HOST = os.environ.get("QINIU_UPLOAD_HOST", "https://up-z1.qiniup.com")
DOWNLOAD_DOMAIN = os.environ.get("QINIU_DOWNLOAD_DOMAIN", "https://file.qingzhou.link").rstrip("/")
PREFIX = os.environ.get("QINIU_PREFIX", "yaozechuan")


def urlsafe_base64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii")


def upload_token(access_key: str, secret_key: str, object_key: str) -> str:
    policy = {
        "scope": f"{BUCKET}:{object_key}",
        "deadline": int(time.time()) + 3600,
    }
    encoded_policy = urlsafe_base64(
        json.dumps(policy, separators=(",", ":")).encode("utf-8")
    )
    signature = urlsafe_base64(
        hmac.new(secret_key.encode("utf-8"), encoded_policy.encode("utf-8"), hashlib.sha1).digest()
    )
    return f"{access_key}:{signature}:{encoded_policy}"


def multipart_body(fields: dict[str, str], filename: str, content: bytes) -> tuple[bytes, str]:
    boundary = f"----XiaomiAssistant{uuid.uuid4().hex}"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            ]
        )
    parts.extend(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: application/json\r\n\r\n",
            content,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    return b"".join(parts), boundary


def main() -> int:
    access_key = os.environ.get("QINIU_ACCESS_KEY")
    secret_key = os.environ.get("QINIU_SECRET_KEY")
    if not access_key or not secret_key:
        print("请先设置环境变量 QINIU_ACCESS_KEY 和 QINIU_SECRET_KEY。", file=sys.stderr)
        return 2

    timestamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    object_key = f"{PREFIX}/test/qiniu-upload-check-{timestamp}.json"
    payload = json.dumps({"status": "ok", "uploadedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}).encode(
        "utf-8"
    )
    body, boundary = multipart_body(
        {"token": upload_token(access_key, secret_key, object_key), "key": object_key},
        "qiniu-upload-check.json",
        payload,
    )
    request = urllib.request.Request(
        UPLOAD_HOST,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
    except Exception as error:
        print(f"七牛上传失败：{error}", file=sys.stderr)
        return 1

    download_url = f"{DOWNLOAD_DOMAIN}/{object_key}?check={timestamp}"
    try:
        with urllib.request.urlopen(download_url, timeout=30) as response:
            downloaded = response.read()
    except Exception as error:
        print(f"上传成功，但公网回读失败：{download_url}，{error}", file=sys.stderr)
        return 1

    if downloaded != payload:
        print(f"上传成功，但公网回读内容不一致：{download_url}", file=sys.stderr)
        return 1

    print("上传与公网回读成功。")
    print(f"对象：{object_key}")
    print(f"地址：{download_url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
