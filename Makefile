.PHONY: clean sass build
build:
	lein build
deploy: build 
	csync-projects.sh mok-web nh "/home/haier/"
