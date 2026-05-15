.PHONY: start stop build test deploy preflight backup backup-replay-blog backup-replay-personalist backup-replay-tracker clean

start:
	@DEV=true clj -X:run

stop:
	@pkill -f 'plurama.server' || true

build:
	clj -T:build uber

test:
	$(MAKE) -C ../personalist test
	$(MAKE) -C ../blog test
	$(MAKE) -C ../tracker test
	$(MAKE) -C ../tracker e2e-docker

backup:
	@mkdir -p ../backups
	@OUT="../backups/plurama-data.$$(date +%Y-%m-%d.%H-%M).tar.gz" && \
		echo "Backing up /app/data from fly machine to $$OUT ..." && \
		fly ssh console --app plurama -C "tar -czf - -C / app/data" > "$$OUT" && \
		echo "Wrote $$OUT ($$(du -h "$$OUT" | cut -f1))"

preflight:
	@set -e; \
	for r in plurama blog tracker personalist; do \
		dir=".."; if [ "$$r" = "plurama" ]; then dir="."; else dir="../$$r"; fi; \
		echo "Preflight: $$r"; \
		branch=$$(git -C "$$dir" rev-parse --abbrev-ref HEAD); \
		if [ "$$branch" != "main" ]; then echo "  ✗ on branch '$$branch', not main"; exit 1; fi; \
		if [ -n "$$(git -C "$$dir" status --porcelain)" ]; then echo "  ✗ working tree not clean"; exit 1; fi; \
		git -C "$$dir" fetch --quiet origin main; \
		local_sha=$$(git -C "$$dir" rev-parse @); \
		remote_sha=$$(git -C "$$dir" rev-parse @{u}); \
		if [ "$$local_sha" != "$$remote_sha" ]; then echo "  ✗ not in sync with origin/main"; exit 1; fi; \
		echo "  ✓ main, clean, in sync"; \
	done

deploy: preflight test backup
	@mkdir -p .build
	@cp ../claude-stuff/plugins/tracker/skills/tracker-api/SKILL.md .build/tracker-api.md
	cd .. && fly deploy --config plurama/fly.toml --dockerfile plurama/Dockerfile

backup-replay-blog:
	@if [ -d ../blog/data ]; then \
		echo "Error: ../blog/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t ../backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in ../backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying blog.db from $$LATEST to ../blog/data/ ..."; \
	tar -xzf "$$LATEST" -C ../blog --strip-components=1 app/data/blog.db && \
	echo "Done."

backup-replay-personalist:
	@if [ -d ../personalist/data ]; then \
		echo "Error: ../personalist/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t ../backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in ../backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying personalist.db from $$LATEST to ../personalist/data/ ..."; \
	tar -xzf "$$LATEST" -C ../personalist --strip-components=1 app/data/personalist.db && \
	echo "Done."

backup-replay-tracker:
	@if [ -d ../tracker/data ]; then \
		echo "Error: ../tracker/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t ../backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in ../backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying tracker.db from $$LATEST to ../tracker/data/ ..."; \
	tar -xzf "$$LATEST" -C ../tracker --strip-components=1 app/data/tracker.db && \
	echo "Done."

clean:
	rm -rf target
