package dev.learning.mall.messaging.web;

import dev.learning.mall.messaging.api.MessageDashboard;
import dev.learning.mall.messaging.internal.MessageService;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    private final MessageService messages;
    public MessageController(MessageService messages) { this.messages = messages; }
    @GetMapping
    public MessageDashboard dashboard(Principal principal) { return messages.dashboard(principal.getName()); }
    @PostMapping("/{id}/retry")
    public void retry(@PathVariable String id, Principal principal) { messages.retry(id, principal.getName()); }
}
