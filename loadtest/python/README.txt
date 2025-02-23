To build:

docker build -t python-rest-api .

To run:

docker run -p 8000:8000 python-rest-api

To use: (against running server)

curl http://localhost:8000/say/hello
