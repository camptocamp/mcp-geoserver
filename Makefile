# GeoServer MCP: common tasks. Run `make` or `make help` to list targets.
#
# Examples:
#   make start           # start (or create) the GeoServer container, then wait
#   make test            # Maven unit tests
#   make build           # package the extension jars
#   make logs            # tail GeoServer logs
#   make remove          # stop and delete the GeoServer container

GS       ?= vanilla-geoserver-1
GS_IMAGE ?= docker.osgeo.org/geoserver:3.0.1
GS_DATA  ?= vanilla_geoserver_data
GS_URL   ?= http://localhost:8080/geoserver
GS_AUTH  ?= admin:geoserver

.DEFAULT_GOAL := help
.PHONY: help start run stop restart recreate remove logs wait status shell web \
        build test it clean scenarios

help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n",$$1,$$2}'

## --- GeoServer container ---
start: ## Start (or create) GeoServer, then wait until ready
	@docker start $(GS) 2>/dev/null || $(MAKE) run
	@$(MAKE) wait

run: ## Create and run the GeoServer container
	docker run -d --name $(GS) -p 8080:8080 \
		-e SKIP_DEMO_DATA=false -e PROXY_BASE_URL=$(GS_URL) \
		-v $(GS_DATA):/opt/geoserver_data \
		-v "$(PWD)/docker/vanilla/additional_libs:/opt/additional_libs" \
		$(GS_IMAGE)

stop: ## Stop the GeoServer container
	docker stop $(GS)

restart: stop start ## Restart GeoServer

recreate: remove run wait ## Delete and recreate the container (keeps the data volume)

remove: ## Stop and delete the GeoServer container (keeps the data volume)
	docker rm -f $(GS)

logs: ## Tail GeoServer logs
	docker logs -f --tail 100 $(GS)

status: ## Show container state and GeoServer version
	@docker ps -a --filter name=$(GS) --format 'table {{.Names}}\t{{.Status}}'
	@curl -s -u $(GS_AUTH) $(GS_URL)/rest/about/version.json -o /dev/null -w 'rest: HTTP %{http_code}\n' || true

shell: ## Open a shell in the GeoServer container
	docker exec -it $(GS) bash

web: ## Print the GeoServer web URL and credentials
	@echo "$(GS_URL)/web  ($(GS_AUTH))"

wait: ## Wait until GeoServer answers (polls from inside the container)
	@docker exec $(GS) sh -c 'for i in $$(seq 1 40); do \
		curl -sf -u $(GS_AUTH) $(GS_URL)/rest/about/version.json >/dev/null && { echo "geoserver ready"; exit 0; }; \
		sleep 3; done; echo "geoserver not ready"; exit 1'


## --- build / test ---
build: ## Package the extension jars (skips tests)
	./mvnw -q -DskipTests clean package

test: ## Maven unit tests
	./mvnw -q test

it: ## Maven integration tests (mcp-it, needs Docker)
	./mvnw -q clean verify -Pit

scenarios: start ## Four model-driven MCP scenarios (MODEL=, BUDGET_EUR= cap per scenario, EUR_PER_USD= rate; needs make build once)
	./scripts/scenarios.sh

clean: ## Maven clean
	./mvnw -q clean
