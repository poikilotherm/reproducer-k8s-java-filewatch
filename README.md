## Deploy to **local** Docker

```
mkdir data
mvn clean package k8s:build
docker run --rm --name devtest -v "$(pwd)"/data:/data -e "ORG_EXAMPLE_DIR=/data" -it example/k8s-watch:latest
```

Then play with adding data to the `data` directory, including subdirectories, symlinks. Remember files and folders
starting with a "." are to be ignored on purpose (K8s usage of mounting subpaths).


## Deploy to Minikube

```
eval $(minikube docker-env)
mvn clean package k8s:build k8s:resource k8s:deploy
```

Test updating a secret by editing it via `kubectl edit secret test1` and watching the logs of the pod. 