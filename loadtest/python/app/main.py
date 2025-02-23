from fastapi import FastAPI
import asyncio

app = FastAPI()

@app.get("/say/hello")
async def say_hello():
    return {"message": "Hello!"}
