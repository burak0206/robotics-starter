package dev.robotics

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RoboticsApplication

fun main(args: Array<String>) {
    runApplication<RoboticsApplication>(*args)
}
