To build:

docker build -t node-fastify-app .

To run:

docker run -p 8000:8000 node-fastify-app

To use: (against running server)

curl http://localhost:8000/say/hello
