.PHONY: start stop build deploy backup backup-replay-blog backup-replay-personalist clean

start:
	@DEV=true clj -X:run

stop:
	@pkill -f 'plurama.server' || true

build:
	clj -T:build uber

backup:
	@mkdir -p backups
	@OUT="backups/plurama-data.$$(date +%Y-%m-%d.%H-%M).tar.gz" && \
		echo "Backing up /app/data from fly machine to $$OUT ..." && \
		fly ssh console --app plurama -C "tar -czf - -C / app/data" > "$$OUT" && \
		echo "Wrote $$OUT ($$(du -h "$$OUT" | cut -f1))"

deploy: backup
	cd .. && fly deploy --config plurama/fly.toml --dockerfile plurama/Dockerfile

backup-replay-blog:
	@if [ -d ../blog/data ]; then \
		echo "Error: ../blog/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying blog.db from $$LATEST to ../blog/data/ ..."; \
	tar -xzf "$$LATEST" -C ../blog --strip-components=1 app/data/blog.db && \
	echo "Done."

backup-replay-personalist:
	@if [ -d ../personalist/data ]; then \
		echo "Error: ../personalist/data already exists. Remove it first."; \
		exit 1; \
	fi
	@LATEST=$$(ls -t backups/plurama-data.*.tar.gz 2>/dev/null | head -1); \
	if [ -z "$$LATEST" ]; then echo "Error: no backup found in backups/. Run 'make backup' first."; exit 1; fi; \
	echo "Replaying personalist.db from $$LATEST to ../personalist/data/ ..."; \
	tar -xzf "$$LATEST" -C ../personalist --strip-components=1 app/data/personalist.db && \
	echo "Done."

clean:
	rm -rf target
