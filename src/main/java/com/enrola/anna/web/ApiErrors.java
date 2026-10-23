package com.enrola.anna.web;

import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.NotFoundException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Maps the service's typed exceptions and rejected requests to status codes and {@link ApiError}.
 * Messages name fields as they appear on the wire and never repeat framework text or raw input.
 */
@RestControllerAdvice
class ApiErrors {

  @ExceptionHandler(NotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  ApiError notFound(NotFoundException e) {
    return new ApiError("NOT_FOUND", e.getMessage(), null);
  }

  @ExceptionHandler(ConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  ApiError conflict(ConflictException e) {
    return new ApiError(e.code(), e.getMessage(), e.conversationId());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError invalidBody(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> wireName(error.getField()) + " " + error.getDefaultMessage())
            .orElse("request body is invalid");
    return validation(message);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError invalidParameter(HandlerMethodValidationException e) {
    String message =
        e.getParameterValidationResults().stream()
            .findFirst()
            .map(
                result ->
                    result.getMethodParameter().getParameterName()
                        + " "
                        + result.getResolvableErrors().getFirst().getDefaultMessage())
            .orElse("request is invalid");
    return validation(message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError unreadable() {
    return validation("request body must be a JSON object");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
  ApiError unsupportedMediaType() {
    return new ApiError("UNSUPPORTED_MEDIA_TYPE", "content type must be application/json", null);
  }

  private static ApiError validation(String message) {
    return new ApiError("VALIDATION", message, null);
  }

  /** leadId to lead_id, matching the app's snake_case Jackson naming. */
  private static String wireName(String field) {
    return field.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
  }
}
