package user.registry.api;

import java.util.Optional;

public record EmailInfo(String address, String status, Optional<String> ownerId) {
}
