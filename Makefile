export JAVA_HOME ?= $(HOME)/Library/Java/JavaVirtualMachines/temurin-25.0.4/Contents/Home

COMPOSE := docker compose

.PHONY: help up down clean e2e test test-java test-web diagrams logs

help:
	@echo "make up        postgres, kafka, three services and the web page on http://localhost:8080"
	@echo "make e2e       run every scenario against the running stack, check outcomes and money"
	@echo "make test      Maven verify (Docker needed for Testcontainers) and web tests"
	@echo "make diagrams  export docs/diagrams/*.mmd to PNG"
	@echo "make logs      follow the three services"
	@echo "make down      stop everything"
	@echo "make clean     stop everything and drop volumes"

up:
	$(COMPOSE) up -d --build --wait

e2e:
	scripts/e2e.sh

test: test-java test-web

test-java:
	./mvnw -B verify

test-web:
	cd web && npm ci && npm run lint && npm test && npm run build

diagrams:
	for f in docs/diagrams/*.mmd; do npx -y @mermaid-js/mermaid-cli@12.2.0 -i $$f -o $${f%.mmd}.png -b white -s 2 -c docs/diagrams/mermaid.json; done

logs:
	$(COMPOSE) logs -f transfer-service account-service risk-service

down:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v
