To build:

docker build -t java-springboot-app .

To run:

docker run -p 8000:8000 java-springboot-app

To use: (against running server)

curl http://localhost:8000/say/hello
