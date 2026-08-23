FROM --platform=linux/arm64 node:22-slim AS build
WORKDIR /app
COPY package*.json ./
# npm install statt npm ci: Claude kann package-lock.json in seiner Sandbox
# nicht aktuell halten (kein Netzwerkzugriff dort), daher weicht sie nach
# jeder neuen Dependency in package.json ab. npm install aktualisiert die
# Lock-Datei bei Bedarf automatisch statt bei Abweichung hart abzubrechen
# (wie npm ci) - dafür etwas weniger strikt reproduzierbar.
RUN npm install
COPY . .
RUN npm run build -- --configuration production

FROM --platform=linux/arm64 nginx:alpine
COPY --from=build /app/dist/stock-client/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
