import { defineConfig } from 'vite';
import { AgentService } from './src/agent/service.js';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  base: './',
  server: { port: 5173, strictPort: false },
  plugins: [{
    name: 'pet-local-service',
    configureServer(server) {
      const root = fileURLToPath(new URL('.', import.meta.url));
      const service = new AgentService(fileURLToPath(new URL('.local-data', import.meta.url)), undefined, root);
      server.middlewares.use('/api/pet', async (request, response) => {
        response.setHeader('Content-Type', 'application/json; charset=utf-8');
        if (request.method !== 'POST') { response.statusCode = 405; response.end('{}'); return; }
        // Only the local preview may invoke the service, which holds provider credentials.
        if (request.headers.origin && request.headers.origin !== `http://${request.headers.host}`) {
          response.statusCode = 403; response.end('{}'); return;
        }
        try {
          let body = '';
          for await (const chunk of request) {
            body += chunk;
            if (body.length > 64_000) throw new Error('请求过大');
          }
          const { action, payload } = JSON.parse(body);
          const result = await service.invoke(action, payload);
          response.end(JSON.stringify(result));
        } catch (error) {
          response.statusCode = 400;
          response.end(JSON.stringify({ error: error instanceof Error ? error.message : '请求失败' }));
        }
      });
    },
  }],
});
