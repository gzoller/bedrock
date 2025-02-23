To build:

docker build -t go-gin-app .

To run:

docker run -p 8000:8000 go-gin-app

To use: (against running server)

curl http://localhost:8000/say/hello
