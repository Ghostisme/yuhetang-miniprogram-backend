UPDATE permission SET name='预览品牌内容', module='品牌与内容' WHERE code='content:read';
UPDATE permission SET name='编辑品牌草稿（总部）', module='品牌与内容' WHERE code='content:write';
UPDATE permission SET name='发布品牌内容（总部）', module='品牌与内容' WHERE code='content:publish';
UPDATE permission SET name='查看本店养护项目', module='品牌与内容' WHERE code='services:read';
UPDATE permission SET name='维护本店养护项目', module='品牌与内容' WHERE code='services:write';
UPDATE permission SET name='查看图片素材', module='品牌与内容' WHERE code='assets:read';
UPDATE permission SET name='上传图片素材', module='品牌与内容' WHERE code='assets:write';
