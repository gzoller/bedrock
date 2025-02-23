# Use official Node.js LTS image
FROM node:20-slim

# Set the working directory
WORKDIR /app

# Copy package.json and install dependencies
COPY package.json ./
RUN npm install --only=production

# Copy application files
COPY server.js ./

# Expose port 8000
EXPOSE 8000

# Run the application
CMD ["node", "server.js"]
