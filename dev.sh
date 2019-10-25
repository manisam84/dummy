$(aws ecr get-login --no-include-email --region ap-southeast-1)

#aws s3 sync assets s3://bryce-assets/grapefruit/ --acl public-read
docker build -t melon .
docker kill melon
docker rm melon
#docker run -d --name grapefruit -p 8080:8080 grapefruit
docker run -d --name melon -p 8081:80 melon
docker tag melon:latest 650143975734.dkr.ecr.ap-southeast-1.amazonaws.com/trendsh-uno-melon
docker push 650143975734.dkr.ecr.ap-southeast-1.amazonaws.com/trendsh-uno-melon

