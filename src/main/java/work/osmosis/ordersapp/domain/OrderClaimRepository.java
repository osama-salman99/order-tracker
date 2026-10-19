package work.osmosis.ordersapp.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, Long> {

    List<OrderClaim> findByClaimIn(Collection<String> claims);

    List<OrderClaim> findByOrderId(Long orderId);

    void deleteByOrderId(Long orderId);
}
