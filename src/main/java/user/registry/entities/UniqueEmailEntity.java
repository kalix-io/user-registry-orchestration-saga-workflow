package user.registry.entities;


import kalix.javasdk.annotations.Acl;
import kalix.javasdk.annotations.Id;
import kalix.javasdk.annotations.TypeId;
import kalix.javasdk.valueentity.ValueEntity;
import kalix.javasdk.valueentity.ValueEntityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import user.registry.Done;

import java.util.Optional;

@Id("id")
@TypeId("unique-address")
@RequestMapping("/unique-emails/{id}")
@Acl(allow = @Acl.Matcher(service = "*"))
public class UniqueEmailEntity extends ValueEntity<UniqueEmailEntity.UniqueEmail> {

  private final String address;
  private Logger logger = LoggerFactory.getLogger(getClass());

  public UniqueEmailEntity(ValueEntityContext context) {
    this.address = context.entityId();
  }

  public enum Status {
    NOT_USED,
    RESERVED,
    CONFIRMED
  }

  @Override
  public UniqueEmail emptyState() {
    return new UniqueEmail(address, Status.NOT_USED, Optional.empty());
  }

  public record UniqueEmail(String address, Status status, Optional<String> ownerId) {

    public boolean sameOwner(String ownerId) {
      return this.ownerId.isPresent() && this.ownerId.get().equals(ownerId);
    }

    public boolean notSameOwner(String ownerId) {
      return !sameOwner(ownerId);
    }

    public UniqueEmail asConfirmed() {
      return new UniqueEmail(address, Status.CONFIRMED, ownerId);
    }

    public boolean isConfirmed() {
      return status == Status.CONFIRMED;
    }

    public boolean isInUse() {
      return status != Status.NOT_USED;
    }

    public boolean isReserved() {
      return status == Status.RESERVED;
    }
  }

  public record ReserveEmail(String address, String ownerId) {
  }

  @PostMapping("/reserve")
  public Effect<Done> reserve(@RequestBody ReserveEmail cmd) {
    if (currentState().isInUse() && currentState().notSameOwner(cmd.ownerId)) {
      return effects().error("Email is already reserved");
    }

    if (currentState().sameOwner(cmd.ownerId)) {
      return effects().reply(Done.done());
    }

    logger.info("Reserving address '{}'", cmd.address());
    return effects()
      .updateState(new UniqueEmail(cmd.address, Status.RESERVED, Optional.of(cmd.ownerId)))
      .thenReply(Done.done());
  }

  @PostMapping("/confirm")
  public Effect<Done> confirm() {
    if (currentState().isReserved()) {
      logger.info("Email is reserved, confirming address '{}'", currentState().address);
      return effects()
        .updateState(currentState().asConfirmed())
        .thenReply(Done.done());
    } else {
      logger.info("Email status is not reserved. Ignoring confirmation request.");
      return effects().reply(Done.done());
    }
  }


  @PostMapping()
  public Effect<Done> unReserve() {
    if (currentState().isReserved()) {
      logger.info("Un-reserving address '{}'", currentState().address);
      return effects()
        .updateState(emptyState())
        .thenReply(Done.done());
    } else {
      return effects().reply(Done.done());
    }
  }

  @DeleteMapping()
  public Effect<Done> delete() {
    logger.info("Deleting address '{}'", currentState().address);
    return effects()
      .updateState(emptyState())
      .thenReply(Done.done());
  }


  @GetMapping
  public Effect<UniqueEmail> getState() {
    return effects().reply(currentState());
  }

}
