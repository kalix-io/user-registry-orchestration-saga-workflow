package user.registry;

public interface Result {

  record Failure(String message) implements Result {}

  record Success(String message) implements Result {}

   static Result failure(String message) {
    return new Failure(message);
  }

  static Result success() {
    return new Success("done");
  }

  default boolean isSuccess() {
    return this instanceof Success;
  }

  default boolean isFailure() {
    return !isSuccess();
  }
}
