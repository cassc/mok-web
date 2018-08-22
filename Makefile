deploy-72: 
	csync-projects.sh mok 

deploy-dev-nh: 
	csync-projects.sh mok nh "/home/haier/"

deploy-prod:
	csync-projects.sh mok uctest "/home/chenli/"

clean-rpc:
	rm -rf ./thrift/gen-java

rpc-java:
	cd ./thrift ; thrift -r -gen java mok-api.thrift

cljs:
	lein do clean, cljbuild once app

clean:
	lein clean

svn:
	svn-projects-sync.sh mok
