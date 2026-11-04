"""Only the Java gateway can establish a user's identity; model arguments cannot."""
import base64
import hashlib
import hmac
import json
import os
import time
from fastapi import Header, HTTPException


def verify_context(service_token: str, context: str) -> dict:
    secret = os.getenv("BUSINESS_SERVICE_TOKEN", "")
    try:
        if not secret or not hmac.compare_digest(secret, service_token):
            raise ValueError("service")
        payload, signature = context.split(".")
        expected = base64.urlsafe_b64encode(hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest()).decode().rstrip("=")
        if not hmac.compare_digest(expected, signature):
            raise ValueError("signature")
        data = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        if data["exp"] <= time.time() or not data["user_id"] or not data["session_id"]:
            raise ValueError("expired")
        return data
    except (ValueError, KeyError, TypeError):
        raise HTTPException(status_code=401, detail="可信内部用户上下文缺失或无效") from None


def internal_identity(x_service_token: str = Header(default=""), x_user_context: str = Header(default="")):
    return {**verify_context(x_service_token, x_user_context), "context": x_user_context}
