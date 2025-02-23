To build:

docker build -t scala3-zio-http .

To run:

docker run -p 8000:8000 scala3-zio-http

To use: (against running server)

curl http://localhost:8080/say/hello
