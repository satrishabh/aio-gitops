# Run tests in a throwaway stage, then a slim non-root runtime with only production deps
FROM python:3.12-alpine AS test
WORKDIR /src
COPY requirements*.txt ./
RUN pip install --no-cache-dir -r requirements.txt -r requirements-dev.txt
COPY . .
RUN python -m pytest -q && touch /tests-passed

FROM python:3.12-alpine
RUN apk upgrade --no-cache && addgroup -S app -g 10001 && adduser -S app -u 10001 -G app
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# depend on the test stage so a failing test fails the image build
COPY --from=test /tests-passed /tmp/tests-passed
COPY app ./app
USER 10001
EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
