import base64
import hashlib
import hmac
import json
import time
import unittest
from unittest.mock import patch
from fastapi import HTTPException
from app.infra.internal_identity import verify_context


class InternalIdentityTests(unittest.TestCase):
    def context(self, exp):
        payload = base64.urlsafe_b64encode(json.dumps({'user_id':'owner','session_id':'s','exp':exp}).encode()).decode().rstrip('=')
        signature = base64.urlsafe_b64encode(hmac.new(b'service-secret',payload.encode(),hashlib.sha256).digest()).decode().rstrip('=')
        return payload+'.'+signature

    @patch.dict('os.environ', {'BUSINESS_SERVICE_TOKEN':'service-secret'})
    def test_service_token_alone_and_forged_identity_are_rejected(self):
        for context in ['', 'forged.signature', self.context(time.time()-1)]:
            with self.assertRaises(HTTPException):
                verify_context('service-secret', context)
        with self.assertRaises(HTTPException):
            verify_context('wrong', self.context(time.time()+60))

    @patch.dict('os.environ', {'BUSINESS_SERVICE_TOKEN':'service-secret'})
    def test_signed_identity_is_preserved(self):
        self.assertEqual(verify_context('service-secret',self.context(time.time()+60))['user_id'],'owner')
