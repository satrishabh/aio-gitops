# Test + build, then run the standalone Next.js server as a non-root user on 8080
FROM node:22-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm test && npm run build

FROM node:22-alpine
RUN apk upgrade --no-cache && addgroup -S app -g 10001 && adduser -S app -u 10001 -G app
WORKDIR /app
ENV NODE_ENV=production PORT=8080 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1
COPY --from=build /src/.next/standalone ./
COPY --from=build /src/.next/static ./.next/static
COPY --from=build /src/public ./public
USER 10001
EXPOSE 8080
CMD ["node", "server.js"]
