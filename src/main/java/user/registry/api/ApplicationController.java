package user.registry.api;


import kalix.javasdk.action.Action;
import kalix.javasdk.client.ComponentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import user.registry.entities.UniqueEmailEntity;
import user.registry.entities.UserEntity;

@RequestMapping("/api")
public class ApplicationController extends Action {

  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final ComponentClient client;

  public ApplicationController(ComponentClient client) {
    this.client = client;
  }


  @GetMapping("/users/{userId}")
  public Effect<UserInfo> getUserInfo(@PathVariable String userId) {

    var res =
      client.forEventSourcedEntity(userId)
        .call(UserEntity::getState)
        .execute()
        .thenApply(user -> {
          var userInfo =
            new UserInfo(
              userId,
              user.name(),
              user.country(),
              user.email());

          logger.info("Getting user info: {}", userInfo);
          return userInfo;
        });

    return effects().asyncReply(res);
  }

  @GetMapping("/emails/{address}")
  public Effect<EmailInfo> getEmailInfo(@PathVariable String address) {
    var res =
      client.forValueEntity(address)
        .call(UniqueEmailEntity::getState)
        .execute()
        .thenApply(email -> {
          var emailInfo =
            new EmailInfo(
              email.address(),
              email.status().toString(),
              email.owner());

          logger.info("Getting email info: {}", emailInfo);
          return emailInfo;
        });

    return effects().asyncReply(res);
  }

}
