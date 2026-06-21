"""API layer: request/response models and route handlers, split out of the app
entrypoint so `main.py` only wires the app together.

`api_router` is the aggregate router the app mounts. Register new route modules
here as the surface grows.
"""

from fastapi import APIRouter

from .health import router as health_router
from .infer import router as infer_router

api_router = APIRouter()
api_router.include_router(health_router)
api_router.include_router(infer_router)
