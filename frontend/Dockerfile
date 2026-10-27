# ==============================================================================
# QualiScan Frontend Multi-Stage Dockerfile
# Stage 1: Build the React SPA with Vite & Node.js 20
# Stage 2: Serve compiled static assets via lightweight Nginx Alpine
# ==============================================================================

# -----------------------------
# Stage 1: Build
# -----------------------------
FROM node:20-alpine AS builder

WORKDIR /app

# Build-time argument for backend REST/WebSocket URL
ARG VITE_API_URL=http://localhost:8080
ENV VITE_API_URL=${VITE_API_URL}

# Install dependencies with lockfile fidelity
COPY package.json package-lock.json ./
RUN npm ci

# Copy source and build static distribution
COPY . .
RUN npm run build

# -----------------------------
# Stage 2: Runtime Web Server
# -----------------------------
FROM nginx:alpine AS runner

# Remove default nginx static assets and virtual host config
RUN rm -rf /usr/share/nginx/html/* /etc/nginx/conf.d/default.conf

# Copy custom Nginx SPA configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy production build artifacts from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
