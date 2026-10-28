# Pinned PHP for the W0a corpus stack: the yang.php oracle family (php:8.3-cli)
# plus mysqli, which the stock cli image lacks. Record both digests in pin.edn.
ARG PHP_IMAGE=php:8.3-cli
FROM ${PHP_IMAGE}
RUN docker-php-ext-install mysqli
WORKDIR /var/www/html
