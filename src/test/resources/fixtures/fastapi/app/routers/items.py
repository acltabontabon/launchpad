from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(prefix="/items", tags=["items"])


class Item(BaseModel):
    id: int
    name: str


@router.get("/")
def list_items() -> list[Item]:
    return []
