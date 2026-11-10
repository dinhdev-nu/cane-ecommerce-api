# CANE backend

Chủ dự án đã cho phép bắt đầu WP01 ngày 10/11/2026 theo kế hoạch tuần tự. Cây và
tên lớp trong skill/tham chiếu là ví dụ; tổ chức code theo thiết kế và chỉ dẫn hiện hành.

Trước khi thêm, sửa, refactor hoặc review backend, đọc skill
[`cane-backend-structure`](.agents/skills/cane-backend-structure/SKILL.md) và
[cấu trúc tham chiếu](.agents/skills/cane-backend-structure/references/backend-structure.md).

Skill là hướng dẫn hỗ trợ, có thể còn thiếu sót; không áp dụng máy móc hoặc coi mọi
chỉ dẫn trong skill là ràng buộc cứng. Khi có ý tưởng cải tiến mà skill chưa đề cập,
nêu vấn đề, lợi ích/tác động và hỏi chủ dự án trước khi áp dụng. Cập nhật skill theo
quyết định đã thống nhất và kinh nghiệm thực tế; giữ đúng yêu cầu/đặc tả hiện hành.

Repo này chứa API và worker Java/Spring Boot. Hợp đồng, thiết kế và backlog chính thức
ở repo `cane-mvp/` cùng workspace; frontend ở `cane-ui-nextjs/`. Nếu làm trong worktree,
tìm repo tài liệu tại workspace gốc thay vì chép một bộ đặc tả khác vào worktree.

- Giữ năm mô-đun nghiệp vụ và quyền sở hữu dữ liệu theo thiết kế. Đặt lớp/hàm ở mô-đun
  có trách nhiệm; chỉ tạo package con khi có code cần dùng.
- Cây mẫu người dùng gửi là tài liệu tham khảo. Giữ cấu trúc phù hợp đang có; chỉ
  đổi khi có lý do cụ thể. Ưu tiên code đúng, rõ và dễ bảo trì. Cân nhắc design
  pattern theo vấn đề thực tế; không ép thêm abstraction để khớp skill/cây mẫu,
  cũng không cắt ràng buộc cần thiết chỉ để code ngắn hoặc đơn giản hơn.
- Không bịa hoặc trình bày suy đoán thành yêu cầu. Phân biệt nguồn tài liệu, hiện
  trạng code và đề xuất. Yêu cầu nghiệp vụ, schema, API, quyền hoặc quyết định kiến
  trúc còn thiếu/chưa rõ phải hỏi chủ dự án trước khi áp dụng. Người dùng cho phép
  chủ động tổ chức code trong thiết kế hiện hành; không xin duyệt từng tên lớp,
  hàm, biến hoặc package con và không dùng lựa chọn đó để thêm yêu cầu mới.
- Application service sở hữu transaction; controller và job gọi service. Script
  dùng thành phần nghiệp vụ/encoder chung theo task; cơ chế CLI chưa quyết định
  trong lượt chuẩn bị. Domain không phụ thuộc HTTP/JDBC/Redis. Repository chứa
  SQL bind và mapping.
- Spring JDBC/SQL, Flyway và PostgreSQL là lựa chọn hiện hành. Không tự thay stack,
  schema, API, thuật ngữ hoặc ranh giới nghiệp vụ để làm test dễ hơn.
- WP01 làm từng task, kiểm đúng phạm vi rồi mới chuyển task tiếp theo. Kế hoạch đang
  được chấp thuận tại [kế hoạch WP01](docs/wp01-implementation-plan.md). Quyết định
  còn thiếu của task phụ thuộc vẫn phải hỏi trước khi áp dụng.
- Khi được phép triển khai, dùng branch/commit có phạm vi rõ, PR và merge qua GitHub
  trên trình duyệt người dùng yêu cầu. Giữ các thay đổi có sẵn; stage đúng file/hunk,
  không gom mọi thay đổi bằng `git add .`, không reset/stash thay đổi của người dùng.
- Dùng Maven Wrapper và runner hiện có. `*Test` là unit; `*IT` là integration trên
  PostgreSQL/Redis thật. Không bỏ test hoặc dùng kết quả PREP-02 để xác nhận WP01.
- Không đưa secret, body nhạy cảm, session/link token hoặc dữ liệu mở bán giả vào Git,
  log, báo cáo, fixture production hay biên nhận thao tác.

Khi thay đổi kiến trúc có ý nghĩa, cập nhật skill/tham chiếu và thiết kế liên quan;
không biến cây tham chiếu thành yêu cầu dựng sẵn toàn bộ scaffold.
