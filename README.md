# Docman AI
A reference implementation of AI enabled document management system.

## Technology Stack
- Spring Boot for REST
- MINIO (Object store)
- MongoDB (Metadata store)
- OpenSearch as Vector DB
- Ollama AI for document insights
- Temporal for document workflow execution
- Kafka to emit document workflow events

## AI Models
- nomic-embed-text
- llama3

## Features
- Support for various document types like PDF, DOC, TXT etc.
- Document upload and download using Pre-signed URLs
- Vector search queries with user prompts
- Metadata lexical search queries

## Setup

### Install Ollama (MacOS)

Docman uses Ollama AI to create text embedding and for document vector search

```shell
brew install ollama
```

Install the following models on Ollama

```shell
ollama pull nomic-embed-text
ollama pull llama3
```

## Running Docman

Run the docker compose to download and setup all the required containers.

```shell
docker-compose -f docker/docker-compose.yml up
```

Once all the containers are running, start the docman application.

```shell
mvn spring-boot:run
```

There is a bruno collection included in the project tha can be used to call the Docman REST API.