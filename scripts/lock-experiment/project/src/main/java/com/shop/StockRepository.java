package com.shop;

public interface StockRepository extends JpaRepository<Stock, Long> {

    /** 재고 차감: SELECT … WHERE product_id = ? AND status = ? FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Stock> findByProductIdAndStatus(Long productId, String status);

    /** 재고 조정: SELECT … WHERE id = ? FOR UPDATE (PK) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Stock> findById(Long id);

    /** 입고: SELECT … WHERE product_id = ? FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.product.id = :productId")
    List<Stock> lockAllOfProduct(Long productId);

    /** 판매 중 목록: 잠금 없음 */
    List<Stock> findByStatus(String status);
}
