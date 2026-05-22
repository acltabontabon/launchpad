from fastapi import FastAPI
from app.routers import items

app = FastAPI(title="Inventory API")
app.include_router(items.router)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app)
