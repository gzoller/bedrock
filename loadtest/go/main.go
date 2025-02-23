package main

import (
	"github.com/gin-gonic/gin"
)

func main() {
	r := gin.Default()

	// Define the /say/hello endpoint
	r.GET("/say/hello", func(c *gin.Context) {
		c.JSON(200, gin.H{"message": "Hello!"})
	})

	// Start the server on port 8000
	r.Run(":8000")
}
