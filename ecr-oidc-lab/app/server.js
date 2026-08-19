const express = require('express');

const app = express();
const PORT = process.env.PORT || 3000;

app.get('/', (req, res) => {
  res.status(200).json({
    message: 'Hello from a securely containerized Node.js app!',
    hostname: require('os').hostname(),
  });
});

// Used by the Docker HEALTHCHECK instruction and by container orchestrators.
app.get('/health', (req, res) => {
  res.status(200).json({ status: 'healthy' });
});

app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`Server listening on port ${PORT}`);
});
