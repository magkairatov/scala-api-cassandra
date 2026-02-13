# Микросервис 005 узлы работы с системами потоковой аналитики
 - REST API для внешних систем
 - Websocket API для подключения внешних систем

## Компиляция
```
sbt compile
```

## Компиляция с публикацией в локальный репозиторий
```
sbt docker:publishLocal
```

## Перенос в репозиторий altyn-i
```
docker tag altynml/altyn-bi-msv-005:latest registry.altyn-i.kz/big-data/big-data/altyn-bi-msv-005:latest
docker push registry.altyn-i.kz/big-data/big-data/altyn-bi-msv-005:latest
```
Для компиляции с последующим переносом в бой нужно явно указывать версию 
```
docker tag altynml/altyn-bi-msv-005:0.0.1 registry.altyn-i.kz/big-data/big-data/altyn-bi-msv-005:0.0.1
docker push registry.altyn-i.kz/big-data/big-data/altyn-bi-msv-005:0.0.1
```

## Запуск
```
docker-compose up -d
```

## Остановка
```
docker-compose down
```

## Логи
Все исполняемые контейнеры отправляют логи в Kafka
Для их просмотра необходимо из читать из Kafka напрямую например так:
```
ssh zookeep
kafka-console-consumer.sh --bootstrap-server broker1.hsbk.nb:9092,broker2.hsbk.nb:9092,broker3.hsbk.nb:9092 --topic altyn_bi_005_logs
```

## Перенос в бой
1. Собрать образ
2. Установить метку (docker tag)
3. Опубликовать проект в Docker репозитории altyn-i
4. На машине, на которой будет запуск в файлу docker-compose.yml прописать нужный образ, который был помечен docker tag.
5. Выполнить команды:
```shell script
docker-compose stop
docker-compose rm -f
docker-compose pull
docker-compose -f docker-compose.yml up -d
```