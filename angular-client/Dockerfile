FROM --platform=linux/arm64 node:22-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration production

FROM --platform=linux/arm64 nginx:alpine
COPY --from=build /app/dist/stock-client/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
