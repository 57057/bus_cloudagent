"""Real HTTP/Redis catalog acceptance; only creates uniquely named demo records.

Run from the project root after starting Java/MySQL/Redis. No keys are deleted and
no global Redis settings are changed. Test product/SKU are deactivated in finally.
"""
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

import redis
import requests
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tests.p0_acceptance import Client  # noqa: E402
from deploy.run_business import redis_defaults  # noqa: E402
from agent.config import get_settings  # noqa: E402


def main():
    import os
    env = {**dotenv_values(ROOT / "business-java/.env"), **os.environ}
    redis_defaults(env, get_settings().redis_url)
    cache = redis.Redis(host=env.get("REDIS_HOST", "127.0.0.1"),
                        port=int(env.get("REDIS_PORT", 6379)),
                        password=env.get("REDIS_PASSWORD") or None,
                        username=env.get("REDIS_USERNAME") or None,
                        ssl=env.get("REDIS_SSL", "false").lower() == "true",
                        db=int(env.get("BUSINESS_REDIS_DATABASE", 0)),
                        decode_responses=True, socket_timeout=2, protocol=2)
    cache.ping()
    prefix = env.get("CATALOG_CACHE_PREFIX", "cloudagent:business:catalog:v1:")
    admin = Client(admin=True)
    credentials = json.loads((ROOT / "runtime/platform-admin.json").read_text())
    admin.login(credentials["username"], credentials["password"])
    stamp = uuid.uuid4().hex[:12]
    product = {"id": "cache-test-" + stamp, "name": "Cache test " + stamp,
               "category": "ECS", "description": "Cache acceptance",
               "scenario": "test", "active": True, "reason": "cache acceptance"}
    sku = {"id": "cache-sku-" + stamp, "product_id": product["id"],
           "name": "Cache test SKU", "region": "cache-region-" + stamp,
           "zone": "cache-zone", "cpu": 2, "memory_gb": 4, "base_cents": 100,
           "disk_cents": 1, "bandwidth_cents": 1, "capacity": 10,
           "active": True, "adapter": "SIMULATED", "images": "ubuntu-22",
           "min_disk": 40, "max_disk": 500, "max_bandwidth": 100,
           "periods": "1,3,12", "grace_days": 7, "refund_days": 7,
           "reason": "cache acceptance"}
    checks = []

    def get(path):
        response = requests.get("http://127.0.0.1:5000/api" + path, timeout=10)
        assert response.status_code == 200, response.status_code
        return response.json()["data"]

    def key(logical):
        generation = cache.get(prefix + "generation")
        assert generation, "Missing generation: is the new Java build running?"
        return prefix + generation + ":" + hashlib.sha256(logical.encode()).hexdigest()

    created = False
    sku_created = False
    try:
        admin.call("POST", "/products", product)
        created = True
        detail = get("/products/" + product["id"])
        detail_key = key("product:" + product["id"])
        assert json.loads(cache.get(detail_key)) == detail
        ttl = cache.pttl(detail_key)
        assert ttl > 0
        time.sleep(1.1)
        assert get("/products/" + product["id"]) == detail
        assert cache.pttl(detail_key) < ttl - 500, "Hit should not rewrite TTL"
        checks.append("detail JSON cache hit and bounded TTL")

        listing = get("/products?keyword=" + stamp)
        assert listing["total"] == 1
        old_generation = cache.get(prefix + "generation")
        admin.call("POST", "/products", {**product, "name": "Updated " + stamp})
        assert cache.get(prefix + "generation") != old_generation
        assert get("/products/" + product["id"])["name"] == "Updated " + stamp
        assert get("/products?keyword=" + stamp)["items"][0]["name"] == "Updated " + stamp
        checks.append("committed product change invalidates detail and list")

        get("/regions")
        old_generation = cache.get(prefix + "generation")
        admin.call("POST", "/skus", sku)
        sku_created = True
        assert cache.get(prefix + "generation") != old_generation
        assert any(r["region"] == sku["region"] for r in get("/regions"))
        assert get("/products/" + product["id"] + "/skus")[0]["available"] == 10
        checks.append("SKU change invalidates regions; inventory read remains live")

        old_generation = cache.get(prefix + "generation")
        admin.call("POST", "/skus", {**sku, "capacity": -1}, expected=400)
        assert cache.get(prefix + "generation") == old_generation
        checks.append("rejected/rolled-back update leaves generation unchanged")

        admin.call("POST", "/products", {**product, "active": False})
        response = requests.get("http://127.0.0.1:5000/api/products/" + product["id"], timeout=10)
        assert response.status_code == 404
        assert get("/products?keyword=" + stamp)["total"] == 0
        checks.append("deactivated product not served from old cache")
    finally:
        if sku_created:
            admin.call("POST", "/skus", {**sku, "active": False, "reason": "cache acceptance finished"})
        if created:
            admin.call("POST", "/products", {**product, "active": False, "reason": "cache acceptance finished"})
        cache.close()
    result = {"checks": checks, "passed": True}
    (ROOT / "runtime/catalog-cache-acceptance.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
