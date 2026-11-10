.PHONY: up down smoke test

up:
	docker compose up --build -d

down:
	docker compose down

smoke:
	python3 scripts/smoke.py

test:
	cd robot-agent && cargo test --locked
	cd backend && ./gradlew test --no-daemon
	cd frontend && npm ci && npm run build
