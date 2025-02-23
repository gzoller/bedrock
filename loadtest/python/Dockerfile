# Use latest Python image
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Copy and install dependencies
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Copy the application files
COPY app ./app

# Expose the application port
EXPOSE 8000

# Start FastAPI server with multi-threading (4 worker processes)
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "4"]
