package dev.robotics

import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(val code: String, val message: String)

@RestControllerAdvice
class RobotErrorHandler {
    @ExceptionHandler(StatusRuntimeException::class)
    fun grpcFailure(error: StatusRuntimeException): ResponseEntity<ApiError> {
        val (status, body) = when (error.status.code) {
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE to
                ApiError("ROBOT_UNAVAILABLE", "Robot servisine ulaşılamıyor.")
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT to
                ApiError("ROBOT_TIMEOUT", "Robot servisi zamanında yanıt vermedi.")
            else -> HttpStatus.BAD_GATEWAY to
                ApiError("ROBOT_ERROR", "Robot servisi isteği tamamlayamadı.")
        }
        return ResponseEntity.status(status).body(body)
    }
}
