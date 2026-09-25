import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// The build target is a plain static directory that Nginx serves. No dev server is part of the
// deployment, and no Node process stays resident after the build.
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // A source map would be served to every visitor, and the demo does not need one in production.
    sourcemap: false,
    chunkSizeWarningLimit: 1200
  },
  server: {
    // Development only: the dev server forwards API calls to the same origin the release uses, so
    // the application code never needs to know which of the two it is running against.
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8088',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.js'],
    globals: true
  }
})
