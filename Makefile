# EduTrack — common tasks. Run `make` for the list.
.DEFAULT_GOAL := help
.PHONY: help up down logs reset api web verify test clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## Start MySQL, Redis, MinIO and Mailpit
	docker compose up -d
	@echo "  api docs   http://localhost:8080/swagger-ui.html"
	@echo "  mailpit    http://localhost:8025"
	@echo "  minio      http://localhost:9001"

down: ## Stop the stack (data is kept)
	docker compose down

logs: ## Tail the stack
	docker compose logs -f

reset: ## Stop the stack and DELETE all local data
	docker compose down -v

api: ## Run the API on :8080 with the local profile
	cd backend && ./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local

web: ## Run the frontend on :5173
	cd frontend && npm run dev

verify: ## Full gate — everything CI runs
	cd backend  && ./mvnw -B verify
	cd frontend && npm run lint && npm run test -- --run && npm run build

test: ## Tests only
	cd backend  && ./mvnw -B test
	cd frontend && npm run test -- --run

clean: ## Remove build output
	cd backend && ./mvnw -q clean
	rm -rf frontend/dist frontend/node_modules/.vite
