# RH Recruitment AI Assistant

An Agentic AI recruitment assistant powered by an LLM. It uses the Model Context Protocol (MCP), retrieval-augmented generation (RAG), Ollama, and Spring AI to search candidates, analyze recruitment data, answer HR questions from internal documents, and send interview invitations.


### Welcome Screen

![Recruitment Assistant Welcome](Screenshots/RH%201.png)

### Candidate Search and Interview Workflow

![Candidate Search and Interview Invitation](Screenshots/RH%202.png)

### Sent Interview Email

![Interview Invitation Email](Screenshots/RH%203.png)

## Features

- Agentic candidate search and recruitment workflows
- MCP tools for candidate, CSV, and email operations
- LLM-powered conversational interactions through Ollama
- RAG answers based on HR interview, evaluation, and scoring PDFs
- Semantic retrieval using Ollama embeddings
- Candidate search by skills or name
- Recruitment status and screening score analysis
- Confirmed interview invitation delivery by email
- Conversation memory maintained per conversation ID

## Architecture

```text
Browser
  |
  v
Agentic AI Client (port 8080)
  - Ollama LLM
  - RAG with HR PDF embeddings
  - Spring AI chat memory
  - MCP client
  |
  v
MCP Tools Server (port 9090)
  - Agent-accessible MCP tools
  - Candidate database operations
  - Recruitment CSV analysis
  - Interview email actions
  |
  v
MySQL Database
```

## Technologies

- Java 21
- Spring Boot
- Spring AI
- Agentic AI
- Ollama LLM
- RAG
- Vector embeddings
- MCP Streamable HTTP
- MySQL
- JPA
- HTML, CSS and JavaScript
- Maven

## Project Structure

```text
RH-AI-Agent/
├── agent-client/
├── mcp-tools-server/
├── Screenshots/
└── README.md
```

## Prerequisites

- Java 21
- Maven or the included Maven Wrapper
- MySQL
- Ollama
- An SMTP server for local email testing

## Ollama Setup

Install Ollama, then download the required models:

```bash
ollama pull qwen2.5:1.5b
ollama pull nomic-embed-text
```

## MySQL Setup

Create the database:

```sql
CREATE DATABASE rh_recruitment;
```

Configure the database connection through environment variables or your local Spring configuration:

```text
DB_URL=jdbc:mysql://localhost:3306/rh_recruitment
DB_USERNAME=<your-username>
DB_PASSWORD=<your-password>
```

The application and MCP server use the same database.

## Email Setup

Configure the SMTP settings locally:

```text
MAIL_HOST=<your-smtp-host>
MAIL_PORT=<your-smtp-port>
MAIL_USERNAME=<your-username>
MAIL_PASSWORD=<your-password>
MAIL_FROM=<your-sender-address>
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
```

Do not commit real SMTP credentials to the repository.

## Running the Project

### 1. Start the MCP Tools Server

```bash
cd mcp-tools-server
.\mvnw.cmd spring-boot:run
```

The server starts on `http://localhost:9090`.

### 2. Start the Agent Client

In a second terminal:

```bash
cd agent-client
.\mvnw.cmd spring-boot:run
```

Open `http://localhost:8080`.

## Example Questions

```text
Find candidates
Find candidates with Python and Machine Learning
Show all candidates
Who is Interview Pending?
Check experience
Analyze her performance
What does 95 mean in the technical screening score?
Send an interview invitation to <candidate name> on <date> at <time>
```

## MCP Tools

The MCP server exposes agent-accessible tools for:

- Searching candidates by skill or name
- Listing candidates
- Filtering candidates by recruitment status
- Analyzing recruitment CSV data
- Sending interview invitations

## HR Knowledge

The agent loads PDF documents from:

```text
agent-client/src/main/resources/knowledge/
```

These documents provide information about the interview process, evaluation criteria, and score interpretation.

## Tests

Run the agent client tests:

```bash
cd agent-client
.\mvnw.cmd test
```

Run the MCP server tests:

```bash
cd mcp-tools-server
.\mvnw.cmd test
```
