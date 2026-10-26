# QualiScan: agent instructions

## Project
QualiScan is a real-time, mixed-product defect inspection system for small manufacturers.
A camera watches items on a conveyor. For each item the system (1) identifies the product
type (bottle cap, printed card, or biscuit), (2) applies that product's defect rules,
(3) outputs PASS or REJECT with a defect type, and (4) sends the result to a backend that
shows live stats and alerts.

## Layout
- cv_service/  Python 3.10+ (we use 3.14), OpenCV, NumPy, scikit-learn
- backend/     Java 17, Spring Boot, Spring Security (JWT), Spring Data JPA, WebSocket (STOMP), PostgreSQL or MySQL
- frontend/    React live dashboard
- data/        raw images/videos, features.csv, snapshots
Flow: Camera -> Python CV -> REST -> Spring Boot -> WebSocket -> React.

## Environment
- Windows, PowerShell. Python venv is at cv_service/venv (activate with .\venv\Scripts\Activate.ps1).
- Run Python commands from cv_service/.

## Rules
- Classical OpenCV first. No deep learning unless asked.
- Simple, readable, commented code. I am a student and must be able to explain every line.
- Python: type hints, docstrings, small reusable functions. No hardcoded thresholds in check functions.
- Do not add libraries beyond what the task lists. Do not create files the task did not ask for.
- Never claim something works unless you ran it. Show real command output (pytest, training runs, etc.).
- Never fabricate data. If you use synthetic data for a test, say so clearly.
- Do not modify data/features.csv except through the tools I run.
- When done, list every file created or changed and the exact commands to run it.

## Status
Done: Days 1-6 code. Next: Day 7 (testing, Docker, README, demo)

## Backend rules (backend/)
- Java 17, Spring Boot 3.x, Maven (use the wrapper: .\mvnw.cmd on Windows), package com.qualiscan.
- No Lombok. Write plain getters/setters/constructors so I can read and explain every line.
- Layers: controller -> service -> repository. DTO classes for requests/responses; never expose entities directly.
- Default profile uses H2 (file database); a "postgres" profile uses PostgreSQL via environment variables. No secrets in code; the JWT secret comes from application.yml or an env variable.
- Validation with jakarta.validation. Consistent JSON error responses via a @RestControllerAdvice.
- Every endpoint must have at least one MockMvc or SpringBootTest test.
