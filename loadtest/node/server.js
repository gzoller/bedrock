const Fastify = require('fastify');

const fastify = Fastify({
    logger: true
});

// Define the /say/hello endpoint
fastify.get('/say/hello', async (request, reply) => {
    return { message: 'Hello!' };
});

// Start the server on port 8000
const start = async () => {
    try {
        await fastify.listen({ port: 8000, host: '0.0.0.0' });
        console.log('Server is running on http://localhost:8000');
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};

start();
