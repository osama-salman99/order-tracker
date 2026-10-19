package work.osmosis.ordersapp.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The addresses a reader can be at.
 *
 * <p>There is only one page. Opening an order swaps what it shows rather than loading anything, but
 * an order still has an address of its own so that it can be linked to, bookmarked, opened in a new
 * tab and left with the browser's back button. That means the server has to hand the same page back
 * for {@code /orders/123} as it does for {@code /}, and let the page work out which of the two views
 * to draw.
 */
@Controller
public class PageController {

    /** One order, by its address. The page itself decides what to show. */
    @GetMapping("/orders/{id}")
    public String order(@PathVariable Long id) {
        return "forward:/index.html";
    }

    /**
     * Where order details used to live, before the two pages became one. Kept so a link someone
     * saved still lands on the order it named rather than on a missing page.
     */
    @GetMapping("/order.html")
    public String movedOn(@RequestParam(name = "id", required = false) Long id) {
        return id == null ? "redirect:/" : "redirect:/orders/" + id;
    }
}
