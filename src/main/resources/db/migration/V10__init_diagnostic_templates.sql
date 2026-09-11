-- =====================================================
-- Создание начального шаблона для седана
-- =====================================================

-- Шаблон
INSERT INTO inspection_templates (name, vehicle_type, description, is_active, created_by, created_at, updated_at)
SELECT 'Стандартный шаблон для седана', 'SEDAN', 'Стандартная анкета для легкового автомобиля', true, NULL, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM inspection_templates WHERE name = 'Стандартный шаблон для седана');

-- Получаем ID шаблона
DO $$
DECLARE
    v_template_id BIGINT;
    v_group_id BIGINT;
BEGIN
    SELECT id INTO v_template_id FROM inspection_templates WHERE name = 'Стандартный шаблон для седана' LIMIT 1;

    -- Если шаблон не найден, выходим
    IF v_template_id IS NULL THEN
        RAISE NOTICE 'Template not found, skipping...';
        RETURN;
    END IF;

    -- =====================================================
    -- Группа: Кузов
    -- =====================================================
    INSERT INTO inspection_groups (template_id, name, sort_order)
    SELECT v_template_id, 'Кузов', 1
    WHERE NOT EXISTS (SELECT 1 FROM inspection_groups WHERE template_id = v_template_id AND name = 'Кузов');

    SELECT id INTO v_group_id FROM inspection_groups WHERE template_id = v_template_id AND name = 'Кузов' LIMIT 1;

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Состояние ЛКП', 'Оценка состояния лакокрасочного покрытия', true, false, true, true, 1
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Состояние ЛКП');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Царапины', 'Наличие и глубина царапин', true, false, true, true, 2
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Царапины');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Вмятины', 'Наличие вмятин и деформаций', true, false, true, true, 3
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Вмятины');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Коррозия', 'Следы ржавчины', true, false, true, true, 4
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Коррозия');

    -- =====================================================
    -- Группа: Салон
    -- =====================================================
    INSERT INTO inspection_groups (template_id, name, sort_order)
    SELECT v_template_id, 'Салон', 2
    WHERE NOT EXISTS (SELECT 1 FROM inspection_groups WHERE template_id = v_template_id AND name = 'Салон');

    SELECT id INTO v_group_id FROM inspection_groups WHERE template_id = v_template_id AND name = 'Салон' LIMIT 1;

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Состояние сидений', 'Оценка состояния обивки и механизмов', true, false, true, true, 1
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Состояние сидений');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Состояние руля', 'Износ рулевого колеса', true, false, true, true, 2
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Состояние руля');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Работа электроники', 'Проверка всех электронных систем', false, true, true, true, 3
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Работа электроники');

    -- =====================================================
    -- Группа: Двигатель
    -- =====================================================
    INSERT INTO inspection_groups (template_id, name, sort_order)
    SELECT v_template_id, 'Двигатель', 3
    WHERE NOT EXISTS (SELECT 1 FROM inspection_groups WHERE template_id = v_template_id AND name = 'Двигатель');

    SELECT id INTO v_group_id FROM inspection_groups WHERE template_id = v_template_id AND name = 'Двигатель' LIMIT 1;

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Внешний вид двигателя', 'Оценка внешнего состояния', true, false, true, true, 1
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Внешний вид двигателя');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Работа двигателя', 'Запуск, работа на холостых, нагрев', false, true, true, true, 2
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Работа двигателя');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Утечки жидкостей', 'Проверка на наличие подтёков', true, false, true, true, 3
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Утечки жидкостей');

    -- =====================================================
    -- Группа: Ходовая часть
    -- =====================================================
    INSERT INTO inspection_groups (template_id, name, sort_order)
    SELECT v_template_id, 'Ходовая часть', 4
    WHERE NOT EXISTS (SELECT 1 FROM inspection_groups WHERE template_id = v_template_id AND name = 'Ходовая часть');

    SELECT id INTO v_group_id FROM inspection_groups WHERE template_id = v_template_id AND name = 'Ходовая часть' LIMIT 1;

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Состояние подвески', 'Оценка амортизаторов и рычагов', true, false, true, true, 1
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Состояние подвески');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Состояние шин', 'Глубина протектора, износ', true, false, true, true, 2
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Состояние шин');

    -- =====================================================
    -- Группа: Документы
    -- =====================================================
    INSERT INTO inspection_groups (template_id, name, sort_order)
    SELECT v_template_id, 'Документы', 5
    WHERE NOT EXISTS (SELECT 1 FROM inspection_groups WHERE template_id = v_template_id AND name = 'Документы');

    SELECT id INTO v_group_id FROM inspection_groups WHERE template_id = v_template_id AND name = 'Документы' LIMIT 1;

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'ПТС', 'Фото паспорта транспортного средства', true, false, false, true, 1
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'ПТС');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'СТС', 'Фото свидетельства о регистрации', true, false, false, true, 2
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'СТС');

    INSERT INTO inspection_items (group_id, title, description, has_photo, has_video, has_rating, is_required, sort_order)
    SELECT v_group_id, 'Сервисная история', 'Записи о техническом обслуживании', true, false, false, false, 3
    WHERE NOT EXISTS (SELECT 1 FROM inspection_items WHERE group_id = v_group_id AND title = 'Сервисная история');

    RAISE NOTICE 'Template initialization completed for template_id: %', v_template_id;
END $$;