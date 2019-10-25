FROM vulnerables/cve-2014-6271
COPY sources.list /etc/apt/sources.list
#RUN apt-get install apache2
RUN apt-get install openssl 
RUN apt-get update
RUN apt-get install -y python-pip
RUN apt-get install -y mysql-server
RUN apt-get install -y apache2.2- apache2.2- 
RUN apt-get install -y apache2-mpm-prefork curl wget php5 php5-mysql php-pear libapache2-mod-php5

#RUN apt-get remove -y apache2
#RUN apt-get install -y apache2=2.2.22-13+deb7u6
#RUN apt-get install -y  apache2-mpm-prefork apache2-dbg
#RUN apt-get install -y apache2-mpm-itk apache2-mpm-prefork libapache2-mod-php5 libapr1-dbg php-pear
RUN pip install --index-url=https://pypi.python.org/simple/ --upgrade pip
RUN pip install awscli 
#RUN apt-get install python
EXPOSE 80
COPY wp /var/www/
COPY main.sh /main.sh
RUN chmod +x /main.sh
RUN rm -rf /var/www/index.html
COPY config.yml /var/www/config.yml
COPY config.php /var/www/config.php
#COPY main.sh /
COPY private_unencrypted.pem /private_unencrypted.pem
ENTRYPOINT ["/main.sh"]
CMD ["default"]