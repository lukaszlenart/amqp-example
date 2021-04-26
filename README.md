# Example AMQP application

This application demonstrates how to implement a redelivery mechanism using RabbitMQ

## Testing with Livestub

- start Docker using `docker-compose up`
- use `setup-livestub-response.http` to setup livestub instance
- start the `Main` class
- execute `test-retry-with-livestub.http` request and watch logs

RabbitMQ console is available at http://localhost:15672/
