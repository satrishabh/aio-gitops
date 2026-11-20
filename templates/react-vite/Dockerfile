# Test + build with Node, serve the static bundle with unprivileged nginx on 8080
FROM node:22-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm test && npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine
USER root
# Changes daily (set by AIO CI) so this layer is never served stale from the build cache.
ARG AIO_PATCH_DATE
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
