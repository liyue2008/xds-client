# XDS Client

项目构建

```bash
mvn clean package -DskipTests
```

创建Docker镜像

```bash
docker build -t xds-client:1.0 .
```

Minikube加载镜像

```bash
minikube image load xds-client:1.0
```

部署服务

```bash
kubectl apply -f xds-client.yaml
```

查看服务

```bash
kubectl get pods
kubectl get svc
```

暴露服务

```bash
kubectl port-forward svc/xds-client 8088:8088
```

访问服务

```bash
curl http://localhost:8088/api/v1/version
```
