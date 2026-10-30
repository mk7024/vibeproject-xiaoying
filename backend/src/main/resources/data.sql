INSERT IGNORE INTO templates (id, title, category, cover_url, description, required_image_count, accepted_mime_types, max_file_size_bytes, tag, sort_order) VALUES
('cloud-pet', '云朵小狗', '萌宠时刻', '/demo/pet.jpg', '让毛茸茸的小伙伴走进轻盈柔软的云朵世界，记录专属于它的可爱瞬间。', 1, 'image/jpeg,image/png', 5242880, '热门', 1),
('seaside-postcard', '寄自海岸的明信片', '旅行灵感', '/demo/coast.jpg', '把海风、日光与旅途中的你，收藏进一张充满夏日气息的明信片。', 2, 'image/jpeg,image/png', 5242880, '精选', 2),
('forever-film', '把心动拍成电影', '人像写真', '/demo/wedding.jpg', '用温柔的电影色调，定格两个人之间值得反复回味的心动时刻。', 2, 'image/jpeg,image/png', 5242880, NULL, 3),
('soft-portrait', '午后柔光写真', '人像写真', '/demo/portrait.jpg', '让午后的柔和光线照亮日常，留下自然、松弛而细腻的人像记忆。', 1, 'image/jpeg,image/png', 5242880, '新上架', 4),
('cat-day', '猫咪的慢生活', '萌宠时刻', '/demo/cat.jpg', '跟随猫咪的步调，在慵懒的日常里发现安静又治愈的小美好。', 1, 'image/jpeg,image/png', 5242880, NULL, 5),
('alpine-escape', '奔赴山野之间', '旅行灵感', '/demo/mountain.jpg', '走向开阔的山野，让清新的自然色彩与自由的旅途成为画面主角。', 1, 'image/jpeg,image/png', 5242880, NULL, 6);
