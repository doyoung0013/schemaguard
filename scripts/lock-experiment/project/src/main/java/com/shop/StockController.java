package com.shop;

@RestController
public class StockController {
    private StockService stockService;

    @PostMapping("/stocks/decrease")
    public void decrease() { stockService.decrease(1L); }

    @PostMapping("/stocks/{id}/adjust")
    public void adjust() { stockService.adjust(1L); }

    @GetMapping("/stocks/on-sale")
    public Object onSale() { return stockService.onSale(); }

    @PostMapping("/stocks/restock")
    public void restock() { stockService.restock(1L); }
}
